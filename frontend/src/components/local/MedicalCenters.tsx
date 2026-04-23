'use client';

import React, { useRef, useState, useEffect } from 'react';
import { Building2, Star, Heart, ChevronLeft, ChevronRight, BedDouble, Stethoscope, Phone } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface MedicalCenter {
  id: string;
  name: string;
  nameEn: string;
  type: 'hospital' | 'clinic';
  address: string;
  addressEn: string;
  phone: string;
  specialties: string[];
  specialtiesEn: string[];
  rating: number;
  image: string;
  hasEmergency?: boolean;
  beds?: number;
}

const qudsayaCenters: MedicalCenter[] = [
  {
    id: '1',
    name: 'مستشفى قدسيا',
    nameEn: 'Qudsaya Hospital',
    type: 'hospital',
    address: 'طريق قدسيا الرئيسي',
    addressEn: 'Qudsaya Main Road',
    phone: '0999111222',
    specialties: ['طوارئ', 'جراحة', 'باطنية', 'أطفال'],
    specialtiesEn: ['Emergency', 'Surgery', 'Internal', 'Pediatrics'],
    rating: 4.6,
    image: 'https://images.unsplash.com/photo-1519494026892-80bbd2d6fd0d?auto=format&fit=crop&w=400&q=80',
    hasEmergency: true,
    beds: 50
  },
  {
    id: '2',
    name: 'مستوصف الشفاء',
    nameEn: 'Al-Shifa Clinic',
    type: 'clinic',
    address: 'ساحة قدسيا المركز',
    addressEn: 'Qudsaya Center Square',
    phone: '0999333444',
    specialties: ['عام', 'أسنان', 'عيون'],
    specialtiesEn: ['General', 'Dental', 'Eyes'],
    rating: 4.4,
    image: 'https://images.unsplash.com/photo-1629909613654-28e377c37b09?auto=format&fit=crop&w=400&q=80'
  },
  {
    id: '3',
    name: 'مستوصف النور',
    nameEn: 'Al-Noor Clinic',
    address: 'الحي الغربي - قدسيا',
    addressEn: 'West District - Qudsaya',
    type: 'clinic',
    phone: '0999555666',
    specialties: ['نسائية', 'أطفال', 'جلدية'],
    specialtiesEn: ['Gynecology', 'Pediatrics', 'Dermatology'],
    rating: 4.3,
    image: 'https://images.unsplash.com/photo-1516549655169-df83a0774514?auto=format&fit=crop&w=400&q=80'
  }
];

const qudsayaDahiaCenters: MedicalCenter[] = [
  {
    id: '1',
    name: 'مستشفى الضاحية',
    nameEn: 'Dahia Hospital',
    type: 'hospital',
    address: 'الساحة الرئيسية - ضاحية قدسيا',
    addressEn: 'Main Square - Qudsaya Dahia',
    phone: '0999777888',
    specialties: ['طوارئ', 'جراحة', 'عظام', 'قلبية'],
    specialtiesEn: ['Emergency', 'Surgery', 'Orthopedics', 'Cardiology'],
    rating: 4.7,
    image: 'https://images.unsplash.com/photo-1519494026892-80bbd2d6fd0d?auto=format&fit=crop&w=400&q=80',
    hasEmergency: true,
    beds: 80
  },
  {
    id: '2',
    name: 'مستوصف الرحمة',
    nameEn: 'Al-Rahma Clinic',
    type: 'clinic',
    address: 'شارع الرئيسي - ضاحية قدسيا',
    addressEn: 'Main Street - Qudsaya Dahia',
    phone: '0999000111',
    specialties: ['عام', 'أسنان', 'تجميل'],
    specialtiesEn: ['General', 'Dental', 'Cosmetic'],
    rating: 4.5,
    image: 'https://images.unsplash.com/photo-1629909613654-28e377c37b09?auto=format&fit=crop&w=400&q=80'
  }
];

const centersByRegion: Record<Region, MedicalCenter[]> = {
  'qudsaya-center': qudsayaCenters,
  'qudsaya-dahia': qudsayaDahiaCenters
};

const typeFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Building2 },
  { id: 'hospital', name: 'مشافي', nameEn: 'Hospitals', icon: BedDouble },
  { id: 'clinic', name: 'مستوصفات', nameEn: 'Clinics', icon: Stethoscope }
];

export default function MedicalCenters() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const centers = centersByRegion[region];

  const [activeType, setActiveType] = useState('all');
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [favorites, setFavorites] = useState<string[]>([]);
  const [showAll, setShowAll] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredCenters = centers.filter(c =>
    activeType === 'all' || c.type === activeType
  );

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
  }, [filteredCenters]);

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
          {isArabic ? '🏥 مراكز طبية' : '🏥 Medical Centers'}
        </h2>

        {/* Type Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {typeFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeType === filter.id;
            
            return (
              <button
                key={filter.id}
                onClick={() => setActiveType(filter.id)}
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
            {filteredCenters.slice(0, 8).map((center) => {
              const isFavorite = favorites.includes(center.id);

              return (
                <div
                  key={center.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img
                      src={center.image}
                      alt=""
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />
                    
                    {/* Favorite Button */}
                    <button
                      onClick={(e) => { e.stopPropagation(); toggleFavorite(center.id); }}
                      className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                    >
                      <Heart
                        className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`}
                      />
                    </button>

                    {/* Type Badge */}
                    <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                      center.type === 'hospital' ? 'bg-rose-500/90 text-white' : 'bg-pink-500/90 text-white'
                    }`}>
                      {center.type === 'hospital' ? (isArabic ? 'مشفى' : 'Hospital') : (isArabic ? 'مستوصف' : 'Clinic')}
                    </span>

                    {/* Emergency Badge */}
                    {center.hasEmergency && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-red-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm animate-pulse">
                        🚨 {isArabic ? 'طوارئ' : 'ER'}
                      </span>
                    )}

                    {/* Beds Count */}
                    {center.beds && (
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 bg-white/90 text-gray-700 text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        🛏️ {center.beds} {isArabic ? 'سرير' : 'beds'}
                      </span>
                    )}
                  </div>

                  {/* Center Info - Airbnb Style */}
                  <div>
                    {/* Name */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
                      {isArabic ? center.name : center.nameEn}
                    </h3>

                    {/* Address */}
                    <p className="text-xs text-gray-500 mb-1 truncate">
                      📍 {isArabic ? center.address : center.addressEn}
                    </p>

                    {/* Specialties */}
                    <div className="flex flex-wrap gap-1 mb-1">
                      {(isArabic ? center.specialties : center.specialtiesEn).slice(0, 2).map((s, i) => (
                        <span key={i} className="text-[9px] px-2 py-0.5 bg-rose-100 text-rose-700 rounded-full">
                          {s}
                        </span>
                      ))}
                    </div>

                    {/* Rating & Phone */}
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{center.rating}</span>
                      </div>
                      <a
                        href={`tel:${center.phone}`}
                        onClick={(e) => e.stopPropagation()}
                        className="text-xs text-rose-600 hover:underline"
                      >
                        📞 {center.phone}
                      </a>
                    </div>
                  </div>
                </div>
              );
            })}

            {/* View All Card */}
            <button
              onClick={() => setShowAll(true)}
              className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
            >
              <div className="aspect-square rounded-xl overflow-hidden relative bg-gray-100">
                <div className="grid grid-cols-2 grid-rows-2 h-full gap-0.5">
                  {filteredCenters.slice(0, 4).map((c, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={c.image} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredCenters.length} {isArabic ? 'مركز' : 'centers'}</span>
                </div>
              </div>
            </button>
          </div>
        </div>
      </div>

      {/* All Items Drawer */}
      <Drawer.Root open={showAll} onOpenChange={setShowAll}>
        <Drawer.Content className="fixed inset-x-0 bottom-0 h-[90vh] bg-white rounded-t-3xl shadow-2xl z-50">
          <div className="px-4 pt-4 pb-3 border-b border-gray-100">
            <Drawer.Handle className="w-10 h-1 bg-gray-300 rounded-full mx-auto mb-3 block" />
            <Drawer.Title className="text-lg font-bold text-right">
              {isArabic ? 'جميع المراكز الطبية' : 'All Medical Centers'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredCenters.length} {isArabic ? 'مركز متاح' : 'centers available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredCenters.map((center) => {
                const isFavorite = favorites.includes(center.id);
                return (
                  <div key={center.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={center.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(center.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${center.type === 'hospital' ? 'bg-rose-500/90 text-white' : 'bg-pink-500/90 text-white'}`}>
                        {center.type === 'hospital' ? (isArabic ? 'مشفى' : 'Hospital') : (isArabic ? 'مستوصف' : 'Clinic')}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? center.name : center.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">📍 {isArabic ? center.address : center.addressEn}</p>
                    <div className="flex flex-wrap gap-1 my-1">
                      {(isArabic ? center.specialties : center.specialtiesEn).slice(0, 2).map((s, i) => (
                        <span key={i} className="text-[9px] px-2 py-0.5 bg-rose-100 text-rose-700 rounded-full">{s}</span>
                      ))}
                    </div>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{center.rating}</span>
                      </div>
                      <a href={`tel:${center.phone}`} onClick={(e) => e.stopPropagation()} className="text-xs text-rose-600 hover:underline">📞</a>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </Drawer.Content>
      </Drawer.Root>
    </section>
  );
}
