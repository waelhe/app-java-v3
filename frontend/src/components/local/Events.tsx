'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Calendar, MapPin, Clock, Users, Sparkles, Heart, ChevronLeft, ChevronRight, Music, Trophy, Palette, Utensils, Briefcase, PartyPopper } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface Event {
  id: string;
  title: string;
  titleEn: string;
  date: string;
  time: string;
  location: string;
  locationEn: string;
  attendees: number;
  image: string;
  type: 'concert' | 'sports' | 'cultural' | 'food' | 'business' | 'festival';
  typeAr: string;
  typeEn: string;
  featured?: boolean;
  trending?: boolean;
  price?: string;
  priceEn?: string;
}

const qudsayaCenterEvents: Event[] = [
  { id: '1', title: 'مهرجان الربيع الثقافي', titleEn: 'Spring Cultural Festival', date: '25 مارس 2025', time: '4:00 مساءً', location: 'ساحة قدسيا', locationEn: 'Qudsaya Square', attendees: 300, image: 'https://images.unsplash.com/photo-1533174072545-7a4b6ad7a6c3?auto=format&fit=crop&w=300&q=80', type: 'festival', typeAr: 'مهرجان', typeEn: 'Festival', featured: true, price: 'مجاني', priceEn: 'Free' },
  { id: '2', title: 'بطولة كرة القدم', titleEn: 'Football Championship', date: '27 مارس 2025', time: '5:00 مساءً', location: 'ملعب قدسيا', locationEn: 'Qudsaya Stadium', attendees: 600, image: 'https://images.unsplash.com/photo-1574629810360-7efbbe195018?auto=format&fit=crop&w=300&q=80', type: 'sports', typeAr: 'رياضة', typeEn: 'Sports', trending: true, price: '5,000 ل.س', priceEn: '5,000 SYP' },
  { id: '3', title: 'حفل موسيقي للفنان الكبير', titleEn: 'Concert by Famous Artist', date: '30 مارس 2025', time: '8:00 مساءً', location: 'مسرح قدسيا', locationEn: 'Qudsaya Theater', attendees: 450, image: 'https://images.unsplash.com/photo-1514320291840-2e0a9bf2a9ae?auto=format&fit=crop&w=300&q=80', type: 'concert', typeAr: 'حفل', typeEn: 'Concert', featured: true, price: '15,000 ل.س', priceEn: '15,000 SYP' },
  { id: '4', title: 'معرض الفن التشكيلي', titleEn: 'Art Exhibition', date: '1 أبريل 2025', time: '10:00 صباحاً', location: 'غاليري قدسيا', locationEn: 'Qudsaya Gallery', attendees: 120, image: 'https://images.unsplash.com/photo-1531243269054-5ebf6f34081e?auto=format&fit=crop&w=300&q=80', type: 'cultural', typeAr: 'ثقافة', typeEn: 'Cultural', price: 'مجاني', priceEn: 'Free' },
  { id: '5', title: 'مهرجان الطعام السوري', titleEn: 'Syrian Food Festival', date: '5 أبريل 2025', time: '12:00 ظهراً', location: 'ساحة قدسيا', locationEn: 'Qudsaya Square', attendees: 500, image: 'https://images.unsplash.com/photo-1555939594-58d7cb561ad1?auto=format&fit=crop&w=300&q=80', type: 'food', typeAr: 'طعام', typeEn: 'Food', trending: true, price: 'دخول مجاني', priceEn: 'Free Entry' },
  { id: '6', title: 'مؤتمر ريادة الأعمال', titleEn: 'Entrepreneurship Conference', date: '10 أبريل 2025', time: '9:00 صباحاً', location: 'مركز قدسيا', locationEn: 'Qudsaya Center', attendees: 200, image: 'https://images.unsplash.com/photo-1540575467063-178a50c2df87?auto=format&fit=crop&w=300&q=80', type: 'business', typeAr: 'أعمال', typeEn: 'Business', featured: true, price: '10,000 ل.س', priceEn: '10,000 SYP' },
];

const qudsayaDahiaEvents: Event[] = [
  { id: '1', title: 'مهرجان الربيع الثقافي', titleEn: 'Spring Cultural Festival', date: '25 مارس 2025', time: '4:00 مساءً', location: 'ساحة الضاحية', locationEn: 'Dahia Square', attendees: 250, image: 'https://images.unsplash.com/photo-1533174072545-7a4b6ad7a6c3?auto=format&fit=crop&w=300&q=80', type: 'festival', typeAr: 'مهرجان', typeEn: 'Festival', featured: true, price: 'مجاني', priceEn: 'Free' },
  { id: '2', title: 'بطولة كرة القدم', titleEn: 'Football Championship', date: '27 مارس 2025', time: '5:00 مساءً', location: 'ملعب الضاحية', locationEn: 'Dahia Stadium', attendees: 500, image: 'https://images.unsplash.com/photo-1574629810360-7efbbe195018?auto=format&fit=crop&w=300&q=80', type: 'sports', typeAr: 'رياضة', typeEn: 'Sports', trending: true, price: '5,000 ل.س', priceEn: '5,000 SYP' },
  { id: '3', title: 'سهرة فنية مميزة', titleEn: 'Special Music Night', date: '29 مارس 2025', time: '9:00 مساءً', location: 'نادي الضاحية', locationEn: 'Dahia Club', attendees: 300, image: 'https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?auto=format&fit=crop&w=300&q=80', type: 'concert', typeAr: 'حفل', typeEn: 'Concert', featured: true, price: '12,000 ل.س', priceEn: '12,000 SYP' },
  { id: '4', title: 'ورشة الفنون التشكيلية', titleEn: 'Art Workshop', date: '2 أبريل 2025', time: '11:00 صباحاً', location: 'مركز الضاحية', locationEn: 'Dahia Center', attendees: 50, image: 'https://images.unsplash.com/photo-1460661419201-fd4cecdf8a8b?auto=format&fit=crop&w=300&q=80', type: 'cultural', typeAr: 'ثقافة', typeEn: 'Cultural', price: '3,000 ل.س', priceEn: '3,000 SYP' },
  { id: '5', title: 'مأدبة الطعام الشرقي', titleEn: 'Eastern Food Feast', date: '6 أبريل 2025', time: '1:00 ظهراً', location: 'مطعم الضاحية', locationEn: 'Dahia Restaurant', attendees: 150, image: 'https://images.unsplash.com/photo-1504674900247-0877df9cc836?auto=format&fit=crop&w=300&q=80', type: 'food', typeAr: 'طعام', typeEn: 'Food', trending: true, price: '8,000 ل.س', priceEn: '8,000 SYP' },
  { id: '6', title: 'منتدى الأعمال السنوي', titleEn: 'Annual Business Forum', date: '12 أبريل 2025', time: '10:00 صباحاً', location: 'فندق الضاحية', locationEn: 'Dahia Hotel', attendees: 180, image: 'https://images.unsplash.com/photo-1475721027785-f74eccf877e2?auto=format&fit=crop&w=300&q=80', type: 'business', typeAr: 'أعمال', typeEn: 'Business', featured: true, price: '15,000 ل.س', priceEn: '15,000 SYP' },
];

const dataByRegion: Record<Region, Event[]> = {
  'qudsaya-center': qudsayaCenterEvents,
  'qudsaya-dahia': qudsayaDahiaEvents
};

const eventTypeFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Sparkles },
  { id: 'concert', name: 'حفلات', nameEn: 'Concerts', icon: Music },
  { id: 'sports', name: 'رياضة', nameEn: 'Sports', icon: Trophy },
  { id: 'cultural', name: 'ثقافة', nameEn: 'Cultural', icon: Palette },
  { id: 'food', name: 'طعام', nameEn: 'Food', icon: Utensils },
  { id: 'business', name: 'أعمال', nameEn: 'Business', icon: Briefcase },
  { id: 'festival', name: 'مهرجانات', nameEn: 'Festivals', icon: PartyPopper },
];

export default function Events() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const events = dataByRegion[region];

  const [activeType, setActiveType] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredEvents = events.filter(event => activeType === 'all' || event.type === activeType);

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
      scrollRef.current.scrollBy({ left: direction === 'left' ? -300 : 300, behavior: 'smooth' });
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
          {isArabic ? '🎉 فعاليات' : '🎉 Events'}
        </h2>

        {/* Event Type Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {eventTypeFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeType === filter.id;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveType(filter.id)}
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
            {filteredEvents.slice(0, 8).map((event) => {
              const isFavorite = favorites.includes(event.id);
              return (
                <div key={event.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={event.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(event.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-bold rounded-full bg-purple-600 text-white">
                      {isArabic ? event.typeAr : event.typeEn}
                    </span>
                    {event.featured && (
                      <span className="absolute top-2 right-10 px-2 py-0.5 text-[10px] font-bold rounded-full bg-gradient-to-r from-amber-400 to-orange-500 text-white">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                    {event.trending && (
                      <span className="absolute top-2 right-10 px-2 py-0.5 text-[10px] font-bold rounded-full bg-white/90 text-purple-600">
                        {isArabic ? 'رائج' : 'Trending'}
                      </span>
                    )}
                    <div className="absolute bottom-2 left-2 right-2">
                      <h3 className="text-sm font-bold text-white line-clamp-2">{isArabic ? event.title : event.titleEn}</h3>
                    </div>
                  </div>
                  <div>
                    <div className="flex items-center gap-3 text-xs text-gray-600 mb-1">
                      <div className="flex items-center gap-1">
                        <Calendar className="w-3.5 h-3.5 text-purple-500" />
                        <span>{event.date}</span>
                      </div>
                      <div className="flex items-center gap-1">
                        <Clock className="w-3.5 h-3.5 text-purple-500" />
                        <span>{event.time}</span>
                      </div>
                    </div>
                    <div className="flex items-center gap-1 text-xs text-gray-500 mb-1">
                      <MapPin className="w-3.5 h-3.5 text-purple-500" />
                      <span className="line-clamp-1">{isArabic ? event.location : event.locationEn}</span>
                    </div>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-1 text-xs text-purple-600 font-medium">
                        <Users className="w-3.5 h-3.5" />
                        <span>{event.attendees}+ {isArabic ? 'حاضر' : 'attending'}</span>
                      </div>
                      <span className="text-xs font-bold text-gray-900">{isArabic ? event.price : event.priceEn}</span>
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
                    {filteredEvents.slice(0, 4).map((e, idx) => (
                      <div key={idx} className="relative overflow-hidden">
                        <img src={e.image} alt="" className="w-full h-full object-cover opacity-80" />
                      </div>
                    ))}
                  </div>
                  <div className="absolute inset-0 bg-black/40 flex flex-col items-center justify-center">
                    <span className="text-white text-lg font-bold">+{filteredEvents.length - 8}</span>
                    <span className="text-white text-sm mt-1">{isArabic ? 'عرض الكل' : 'View All'}</span>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع الفعاليات' : 'Browse all events'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع الفعاليات */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              🎉 {isArabic ? 'جميع الفعاليات' : 'All Events'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredEvents.map((event) => {
                const isFavorite = favorites.includes(event.id);
                return (
                  <div key={event.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={event.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(event.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className="absolute top-2 left-2 px-2 py-0.5 text-[10px] font-bold rounded-full bg-purple-600 text-white">
                        {isArabic ? event.typeAr : event.typeEn}
                      </span>
                      <div className="absolute bottom-2 left-2 right-2">
                        <h3 className="text-sm font-bold text-white line-clamp-1">{isArabic ? event.title : event.titleEn}</h3>
                      </div>
                    </div>
                    <div>
                      <div className="flex items-center gap-2 text-xs text-gray-600 mb-1">
                        <div className="flex items-center gap-1">
                          <Calendar className="w-3 h-3 text-purple-500" />
                          <span>{event.date}</span>
                        </div>
                      </div>
                      <div className="flex items-center gap-1 text-xs text-gray-500">
                        <MapPin className="w-3 h-3 text-purple-500" />
                        <span className="line-clamp-1">{isArabic ? event.location : event.locationEn}</span>
                      </div>
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
