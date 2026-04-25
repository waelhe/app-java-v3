'use client';

import React, { useState, useRef, useEffect } from 'react';
import { 
  Cake, Flower2, UtensilsCrossed, PartyPopper, Camera, Gift,
  Star, MapPin, Clock, Phone, Heart, ChevronLeft, ChevronRight, Truck, Grid3X3
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface EventService {
  id: string;
  name: string;
  nameEn: string;
  type: string;
  typeEn: string;
  category: 'sweets' | 'flowers' | 'bakery' | 'rental' | 'studio' | 'gifts';
  image: string;
  location: string;
  hours: string;
  phone: string;
  services: string[];
  servicesEn: string[];
  delivery?: boolean;
  rating: number;
  reviews: number;
  price?: string;
  featured?: boolean;
  new?: boolean;
}

const qudsayaCenterEvents: EventService[] = [
  { id: 'sw1', name: 'محل حلويات السلطان', nameEn: 'Sultan Sweets', type: 'حلويات', typeEn: 'Sweets', category: 'sweets', image: 'https://images.unsplash.com/photo-1558301211-0d8c8ddee6ec?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', hours: '8:00 - 22:00', phone: '0999111101', services: ['كيك', 'حلويات شرقية', 'شوكولاتة'], servicesEn: ['Cake', 'Eastern Sweets', 'Chocolate'], delivery: true, rating: 4.9, reviews: 285, price: '2000 - 50000', featured: true },
  { id: 'sw2', name: 'حلويات الأمانة', nameEn: 'Al-Amana Sweets', type: 'حلويات', typeEn: 'Sweets', category: 'sweets', image: 'https://images.unsplash.com/photo-1571115177098-24ec42ed204d?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - شارع الرئيسي', hours: '9:00 - 21:00', phone: '0999111102', services: ['حلويات', 'بقلاوة', 'كنافة'], servicesEn: ['Sweets', 'Baklava', 'Kunafa'], delivery: true, rating: 4.7, reviews: 195, new: true },
  { id: 'sw3', name: 'محل الشوكولاتة', nameEn: 'Chocolate Shop', type: 'شوكولاتة', typeEn: 'Chocolate', category: 'sweets', image: 'https://images.unsplash.com/photo-1549007994-cb92caebd54b?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الحي الغربي', hours: '10:00 - 22:00', phone: '0999111103', services: ['شوكولاتة', 'هدايا', 'بوكسات'], servicesEn: ['Chocolate', 'Gifts', 'Boxes'], delivery: true, rating: 4.8, reviews: 165, featured: true },
  { id: 'fl1', name: 'محل الزهور الجميلة', nameEn: 'Beautiful Flowers', type: 'زهور', typeEn: 'Flowers', category: 'flowers', image: 'https://images.unsplash.com/photo-1487530811176-3780de880c2d?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', hours: '8:00 - 21:00', phone: '0999222201', services: ['باقات', 'تنسيق زهور', 'ورود'], servicesEn: ['Bouquets', 'Flower Arrangement', 'Roses'], delivery: true, rating: 4.8, reviews: 175, featured: true },
  { id: 'fl2', name: 'زهرة الربيع', nameEn: 'Spring Flower', type: 'زهور', typeEn: 'Flowers', category: 'flowers', image: 'https://images.unsplash.com/photo-1561181286-d3fee7d55364?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - شارع الرئيسي', hours: '9:00 - 20:00', phone: '0999222202', services: ['زهور', 'نباتات', 'هدايا'], servicesEn: ['Flowers', 'Plants', 'Gifts'], delivery: true, rating: 4.6, reviews: 125 },
  { id: 'bk1', name: 'مخبز النور', nameEn: 'Al-Noor Bakery', type: 'مخبز', typeEn: 'Bakery', category: 'bakery', image: 'https://images.unsplash.com/photo-1509440159596-0249088772e0?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', hours: '6:00 - 21:00', phone: '0999333301', services: ['خبز', 'معجنات', 'فطائر'], servicesEn: ['Bread', 'Pastries', 'Pies'], delivery: true, rating: 4.7, reviews: 220, featured: true },
  { id: 'st1', name: 'استوديو التصوير', nameEn: 'Photography Studio', type: 'استوديو', typeEn: 'Studio', category: 'studio', image: 'https://images.unsplash.com/photo-1516035069371-29a1b244cc32?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', hours: '10:00 - 20:00', phone: '0999555501', services: ['تصوير', 'مونتاج', 'طباعة'], servicesEn: ['Photography', 'Editing', 'Printing'], rating: 4.9, reviews: 185, featured: true },
  { id: 'gf1', name: 'محل الهدايا', nameEn: 'Gifts Shop', type: 'هدايا', typeEn: 'Gifts', category: 'gifts', image: 'https://images.unsplash.com/photo-1549465220-1a8b9238cd48?auto=format&fit=crop&w=300&q=80', location: 'قدسيا - الساحة', hours: '9:00 - 21:00', phone: '0999666601', services: ['هدايا', 'تغليف', 'بطاقات'], servicesEn: ['Gifts', 'Wrapping', 'Cards'], delivery: true, rating: 4.6, reviews: 115, featured: true },
];

const qudsayaDahiaEvents: EventService[] = [
  { id: 'dsw1', name: 'حلويات الضاحية', nameEn: 'Dahia Sweets', type: 'حلويات', typeEn: 'Sweets', category: 'sweets', image: 'https://images.unsplash.com/photo-1558301211-0d8c8ddee6ec?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', hours: '8:00 - 22:00', phone: '0999111104', services: ['كيك', 'حلويات', 'معجنات'], servicesEn: ['Cake', 'Sweets', 'Pastries'], delivery: true, rating: 4.8, reviews: 225, featured: true },
  { id: 'dsw2', name: 'حلويات النور', nameEn: 'Al-Noor Sweets', type: 'حلويات', typeEn: 'Sweets', category: 'sweets', image: 'https://images.unsplash.com/photo-1571115177098-24ec42ed204d?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الحي الرئيسي', hours: '9:00 - 21:00', phone: '0999111105', services: ['حلويات شرقية', 'كنافة'], servicesEn: ['Eastern Sweets', 'Kunafa'], delivery: true, rating: 4.6, reviews: 165 },
  { id: 'dfl1', name: 'زهور الضاحية', nameEn: 'Dahia Flowers', type: 'زهور', typeEn: 'Flowers', category: 'flowers', image: 'https://images.unsplash.com/photo-1487530811176-3780de880c2d?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', hours: '8:00 - 21:00', phone: '0999222203', services: ['باقات', 'ورود', 'هدايا'], servicesEn: ['Bouquets', 'Roses', 'Gifts'], delivery: true, rating: 4.7, reviews: 155, new: true },
  { id: 'dbk1', name: 'مخبز الضاحية', nameEn: 'Dahia Bakery', type: 'مخبز', typeEn: 'Bakery', category: 'bakery', image: 'https://images.unsplash.com/photo-1509440159596-0249088772e0?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', hours: '6:00 - 21:00', phone: '0999333303', services: ['خبز', 'معجنات'], servicesEn: ['Bread', 'Pastries'], rating: 4.6, reviews: 185 },
  { id: 'dst1', name: 'استوديو الضاحية', nameEn: 'Dahia Studio', type: 'استوديو', typeEn: 'Studio', category: 'studio', image: 'https://images.unsplash.com/photo-1516035069371-29a1b244cc32?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', hours: '10:00 - 20:00', phone: '0999555503', services: ['تصوير', 'طباعة'], servicesEn: ['Photography', 'Printing'], rating: 4.7, reviews: 125, featured: true },
  { id: 'dgf1', name: 'هدايا الضاحية', nameEn: 'Dahia Gifts', type: 'هدايا', typeEn: 'Gifts', category: 'gifts', image: 'https://images.unsplash.com/photo-1549465220-1a8b9238cd48?auto=format&fit=crop&w=300&q=80', location: 'الضاحية - الساحة', hours: '9:00 - 21:00', phone: '0999666603', services: ['هدايا', 'تغليف'], servicesEn: ['Gifts', 'Wrapping'], rating: 4.5, reviews: 95 },
];

const dataByRegion: Record<Region, EventService[]> = {
  'qudsaya-center': qudsayaCenterEvents,
  'qudsaya-dahia': qudsayaDahiaEvents
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: PartyPopper },
  { id: 'sweets', name: 'حلويات', nameEn: 'Sweets', icon: Cake },
  { id: 'flowers', name: 'زهور', nameEn: 'Flowers', icon: Flower2 },
  { id: 'bakery', name: 'أفران', nameEn: 'Bakery', icon: UtensilsCrossed },
  { id: 'studio', name: 'تصوير', nameEn: 'Studio', icon: Camera },
  { id: 'gifts', name: 'هدايا', nameEn: 'Gifts', icon: Gift },
];

export default function EventServices() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const events = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredEvents = events.filter(e => activeCategory === 'all' || e.category === activeCategory);

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
  }, [filteredEvents]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '🎭 خدمات فعاليات' : '🎭 Event Services'}
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
                  isActive ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
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
          {canScrollLeft && (
            <button onClick={() => scroll('left')} className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button onClick={() => scroll('right')} className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          <div ref={scrollRef} className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {filteredEvents.slice(0, 8).map((service) => {
              const isFavorite = favorites.includes(service.id);
              return (
                <div key={service.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={service.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-rose-600/90 text-white">
                      {isArabic ? service.type : service.typeEn}
                    </span>
                    <div className="absolute bottom-2 right-2 flex gap-1">
                      {service.delivery && (
                        <span className="px-2 py-0.5 text-[9px] font-bold rounded-full bg-emerald-500 text-white flex items-center gap-0.5">
                          <Truck className="w-2.5 h-2.5" />
                          {isArabic ? 'توصيل' : 'Delivery'}
                        </span>
                      )}
                      {service.featured && (
                        <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-white/90 text-gray-900">
                          {isArabic ? 'مميز' : 'Featured'}
                        </span>
                      )}
                      {service.new && (
                        <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-blue-500 text-white">
                          {isArabic ? 'جديد' : 'New'}
                        </span>
                      )}
                    </div>
                  </div>
                  <div>
                    <div className="flex items-center gap-1 mb-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{service.rating}</span>
                      <span className="text-xs text-gray-400">({service.reviews})</span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <div className="flex flex-wrap gap-1 mb-1">
                      {(isArabic ? service.services : service.servicesEn).slice(0, 2).map((srv, i) => (
                        <span key={i} className="text-[9px] px-2 py-0.5 bg-rose-100 text-rose-700 rounded-full">{srv}</span>
                      ))}
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {service.location}</span>
                      <a href={`tel:${service.phone}`} onClick={(e) => e.stopPropagation()} className="text-rose-600 hover:underline flex-shrink-0">📞</a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredEvents.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors">
                  <div className="grid grid-cols-2 gap-0.5 h-full">
                    {filteredEvents.slice(0, 4).map((s, idx) => (
                      <div key={idx} className="relative overflow-hidden">
                        <img src={s.image} alt="" className="w-full h-full object-cover opacity-80" />
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-0 bg-black/40 flex flex-col items-center justify-center">
                    <span className="text-white text-lg font-bold">+{filteredEvents.length - 8}</span>
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

      {/* Drawer لعرض جميع خدمات الفعاليات */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              🎭 {isArabic ? 'جميع خدمات الفعاليات' : 'All Event Services'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredEvents.map((service) => {
                const isFavorite = favorites.includes(service.id);
                return (
                  <div key={service.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={service.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm bg-rose-600/90 text-white">
                        {isArabic ? service.type : service.typeEn}
                      </span>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">📍 {service.location}</p>
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
