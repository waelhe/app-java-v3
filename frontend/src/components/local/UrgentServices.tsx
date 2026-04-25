'use client';

import React, { useRef, useState, useEffect } from 'react';
import { Pill, Phone, Clock, Stethoscope, Flame, Wrench, ChevronLeft, ChevronRight, Star, Heart, Grid3X3 } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface Service {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  phone: string;
  hours?: string;
  isOpen: boolean;
  image?: string;
  rating?: number;
  is24h?: boolean;
}

const qudsayaDahiaServices: Service[] = [
  { id: 'p1', name: 'صيدلية الشفاء', nameEn: 'Al-Shifa Pharmacy', type: 'صيدلية', typeEn: 'Pharmacy', phone: '0999123456', hours: '24 ساعة', isOpen: true, is24h: true, rating: 4.8, image: 'https://images.unsplash.com/photo-1631549916768-4119b2e5f926?auto=format&fit=crop&w=400&q=80' },
  { id: 'p2', name: 'صيدلية النور', nameEn: 'Al-Noor Pharmacy', type: 'صيدلية', typeEn: 'Pharmacy', phone: '0998765432', hours: '8 ص - 12 ص', isOpen: true, rating: 4.6, image: 'https://images.unsplash.com/photo-1576602976047-174e57a47881?auto=format&fit=crop&w=400&q=80' },
  { id: 'd1', name: 'د. أحمد الخالد', nameEn: 'Dr. Ahmed Al-Khaled', type: 'طبيب', typeEn: 'Doctor', phone: '0999345678', hours: '24 ساعة', isOpen: true, is24h: true, rating: 4.9, image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?auto=format&fit=crop&w=400&q=80' },
  { id: 'd2', name: 'د. سارة العمري', nameEn: 'Dr. Sara Al-Omari', type: 'طبيب', typeEn: 'Doctor', phone: '0999456789', hours: '9 ص - 9 م', isOpen: true, rating: 4.7, image: 'https://images.unsplash.com/photo-1559839734-2b71ea197ec2?auto=format&fit=crop&w=400&q=80' },
  { id: 'e1', name: 'الدفاع المدني', nameEn: 'Civil Defense', type: 'طوارئ', typeEn: 'Emergency', phone: '113', hours: '24 ساعة', isOpen: true, is24h: true, rating: 5.0, image: 'https://images.unsplash.com/photo-1597466599360-3b9775841aec?auto=format&fit=crop&w=400&q=80' },
];

const qudsayaCenterServices: Service[] = [
  { id: 'cp1', name: 'صيدلية القدس', nameEn: 'Al-Quds Pharmacy', type: 'صيدلية', typeEn: 'Pharmacy', phone: '0999222333', hours: '24 ساعة', isOpen: true, is24h: true, rating: 4.7, image: 'https://images.unsplash.com/photo-1631549916768-4119b2e5f926?auto=format&fit=crop&w=400&q=80' },
  { id: 'cp2', name: 'صيدلية الشفاء', nameEn: 'Al-Shifa Pharmacy', type: 'صيدلية', typeEn: 'Pharmacy', phone: '0999333444', hours: '8 ص - 10 م', isOpen: true, rating: 4.5, image: 'https://images.unsplash.com/photo-1576602976047-174e57a47881?auto=format&fit=crop&w=400&q=80' },
  { id: 'cd1', name: 'د. محمود القدسي', nameEn: 'Dr. Mahmoud Al-Qudsi', type: 'طبيب', typeEn: 'Doctor', phone: '0999555666', hours: '8 ص - 8 م', isOpen: true, rating: 4.8, image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?auto=format&fit=crop&w=400&q=80' },
  { id: 'cd2', name: 'د. فاطمة الزهراء', nameEn: 'Dr. Fatima Al-Zahra', type: 'طبيب', typeEn: 'Doctor', phone: '0999677889', hours: '10 ص - 10 م', isOpen: true, rating: 4.9, image: 'https://images.unsplash.com/photo-1559839734-2b71ea197ec2?auto=format&fit=crop&w=400&q=80' },
  { id: 'ce1', name: 'الدفاع المدني', nameEn: 'Civil Defense', type: 'طوارئ', typeEn: 'Emergency', phone: '113', hours: '24 ساعة', isOpen: true, is24h: true, rating: 5.0, image: 'https://images.unsplash.com/photo-1597466599360-3b9775841aec?auto=format&fit=crop&w=400&q=80' },
];

const allServicesByRegion: Record<Region, Service[]> = {
  'qudsaya-center': qudsayaCenterServices,
  'qudsaya-dahia': qudsayaDahiaServices
};

const typeIcons: Record<string, React.ElementType> = {
  'صيدلية': Pill,
  'طبيب': Stethoscope,
  'طوارئ': Flame,
  'خدمة': Wrench
};

const typeColors: Record<string, { badge: string; icon: string }> = {
  'صيدلية': { badge: 'bg-emerald-500/90 text-white', icon: 'bg-emerald-500/90' },
  'طبيب': { badge: 'bg-sky-500/90 text-white', icon: 'bg-sky-500/90' },
  'طوارئ': { badge: 'bg-red-500/90 text-white', icon: 'bg-red-500/90' },
  'خدمة': { badge: 'bg-slate-500/90 text-white', icon: 'bg-slate-500/90' }
};

// Category filters
const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Grid3X3 },
  { id: 'pharmacy', name: 'صيدليات', nameEn: 'Pharmacies', icon: Pill },
  { id: 'doctor', name: 'أطباء', nameEn: 'Doctors', icon: Stethoscope },
  { id: 'emergency', name: 'طوارئ', nameEn: 'Emergency', icon: Flame },
  { id: '24h', name: '24 ساعة', nameEn: '24 Hours', icon: Clock },
];

export default function UrgentServices() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const allServices = allServicesByRegion[region];

  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Filter services based on active category
  const filteredServices = allServices.filter(service => {
    if (activeCategory === 'all') return true;
    if (activeCategory === 'pharmacy') return service.type === 'صيدلية';
    if (activeCategory === 'doctor') return service.type === 'طبيب';
    if (activeCategory === 'emergency') return service.type === 'طوارئ';
    if (activeCategory === '24h') return service.is24h === true;
    return true;
  });

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [allServices]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({
        left: direction === 'left' ? -280 : 280,
        behavior: 'smooth'
      });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev =>
      prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]
    );
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '🚨 خدمات مناوبة' : '🚨 On-Duty Services'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive 
                    ? 'border-gray-900 text-gray-900' 
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        {/* Horizontal Scrolling Container - Airbnb Style */}
        <div className="relative">
          {/* Navigation Buttons */}
          {canScrollLeft && (
            <button
              onClick={() => scroll('left')}
              className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button
              onClick={() => scroll('right')}
              className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          {/* Scrollable Cards */}
          <div
            ref={scrollRef}
            className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide"
            style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
          >
            {filteredServices.slice(0, 8).map((service) => {
              const isFavorite = favorites.includes(service.id);
              const Icon = typeIcons[service.type] || Pill;
              const colors = typeColors[service.type] || typeColors['خدمة'];

              return (
                <div
                  key={service.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img
                      src={service.image || 'https://images.unsplash.com/photo-1631549916768-4119b2e5f926?auto=format&fit=crop&w=400&q=80'}
                      alt=""
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />

                    {/* Favorite Button */}
                    <button
                      onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }}
                      className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                    >
                      <Heart
                        className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`}
                      />
                    </button>

                    {/* Status Badge */}
                    <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                      service.isOpen ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'
                    }`}>
                      {service.isOpen ? (isArabic ? 'مفتوح' : 'Open') : (isArabic ? 'مغلق' : 'Closed')}
                    </span>

                    {/* 24h Badge */}
                    {service.is24h && (
                      <span className="absolute top-2 right-10 px-2 py-0.5 bg-green-600/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        24H
                      </span>
                    )}

                    {/* Type Badge */}
                    <span className={`absolute bottom-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${colors.badge}`}>
                      {isArabic ? service.type : service.typeEn}
                    </span>

                    {/* Call Button */}
                    <a
                      href={`tel:${service.phone}`}
                      onClick={(e) => e.stopPropagation()}
                      className="absolute bottom-2 right-2 p-2 bg-white/90 rounded-full backdrop-blur-sm hover:bg-white hover:scale-110 transition-all shadow-sm"
                    >
                      <Phone className="w-4 h-4 text-emerald-600" />
                    </a>
                  </div>

                  {/* Service Info - Airbnb Style */}
                  <div>
                    {/* Rating */}
                    {service.rating && (
                      <div className="flex items-center gap-0.5 mb-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{service.rating}</span>
                      </div>
                    )}

                    {/* Name */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
                      {isArabic ? service.name : service.nameEn}
                    </h3>

                    {/* Hours & Phone */}
                    <div className="flex items-center justify-between">
                      {service.hours && (
                        <span className="text-xs text-gray-500 flex items-center gap-0.5">
                          <Clock className="w-3 h-3" />
                          {service.hours}
                        </span>
                      )}
                      <a
                        href={`tel:${service.phone}`}
                        onClick={(e) => e.stopPropagation()}
                        className="text-xs text-emerald-600 hover:underline font-medium"
                      >
                        📞 {service.phone}
                      </a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredServices.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors">
                  <div className="grid grid-cols-2 gap-0.5 h-full">
                    {filteredServices.slice(0, 4).map((s, idx) => (
                      <div key={idx} className="relative overflow-hidden">
                        <img src={s.image} alt="" className="w-full h-full object-cover opacity-80" />
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-0 bg-black/40 flex flex-col items-center justify-center">
                    <span className="text-white text-lg font-bold">+{filteredServices.length - 8}</span>
                    <span className="text-white text-sm mt-1">{isArabic ? 'عرض الكل' : 'View All'}</span>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع الخدمات' : 'Browse all services'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع الخدمات */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              🚨 {isArabic ? 'جميع الخدمات المناوبة' : 'All On-Duty Services'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredServices.map((service) => {
                const isFavorite = favorites.includes(service.id);
                const Icon = typeIcons[service.type] || Pill;
                const colors = typeColors[service.type] || typeColors['خدمة'];

                return (
                  <div key={service.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img
                        src={service.image || 'https://images.unsplash.com/photo-1631549916768-4119b2e5f926?auto=format&fit=crop&w=400&q=80'}
                        alt=""
                        className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                      />
                      <button
                        onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }}
                        className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                      >
                        <Heart
                          className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`}
                        />
                      </button>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${colors.badge}`}>
                        {isArabic ? service.type : service.typeEn}
                      </span>
                      <a
                        href={`tel:${service.phone}`}
                        onClick={(e) => e.stopPropagation()}
                        className="absolute bottom-2 right-2 p-2 bg-white/90 rounded-full backdrop-blur-sm hover:bg-white hover:scale-110 transition-all shadow-sm"
                      >
                        <Phone className="w-4 h-4 text-emerald-600" />
                      </a>
                    </div>
                    <div>
                      {service.rating && (
                        <div className="flex items-center gap-0.5 mb-0.5">
                          <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                          <span className="text-xs font-medium">{service.rating}</span>
                        </div>
                      )}
                      <h3 className="text-sm font-medium text-gray-900 line-clamp-1">
                        {isArabic ? service.name : service.nameEn}
                      </h3>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </DrawerContent>
      </Drawer>
    </section>
  );
}
